/* 
 * time to make versions of ProcModR and ProcEvents with Ctk CtkPMod will actually work 
 * more like CtkEvent with routing, recording, OSCresponder handling, releaseFunc, onReleaseFunc,  
 * also - make an addParameter method that adds a getter and setter for a parameter that can be used 
 * inside the loop?	
 */ 

CtkPMod : CtkObj {
 	classvar <envsd;
 	var <starttime, <condition, <amp, <id, <server, <function, <>version;
 	var <isPlaying = false, <isReleasing = false, isRecording = false, releaseTime = 0.0, <timer, clock;
 	var <>recordPath, <>headerFormat = "wav", <>sampleFormat = "float", hdr;
 	var <audioIn, <routebus, <outbus, <numChannels;
 	var <>releaseFunc, <>onReleaseFunc, <responder, <>initFunc, <>internalReleaseFunc;
 	var <parameters, <inputOptions;
 	var <inc, notes, watch, playinit, addAction, target, <group, <wrapGroup, envSynth;
 	var <endtime, endtimeud, noFunc = false, cper, <>hasGUI = false, <gui;
 	var <ctkPEvents, <>ampFunc, <routeOut, <routeAmp;
 	
 	*new {arg starttime = 0.0, condition, amp = 1, id, outbus = 0, numChannels = 1, 
	 		audioIn, numInChannels, addAction = 0, target = 1, server;
	 	^super.newCopyArgs(Dictionary.new, nil, starttime, condition, amp, id, server)
	 		.initCPMod(addAction, target, outbus, numChannels, audioIn, numInChannels);
 	}

 	initCPMod {arg argAddAction, argTarget, argoutbus, argNumChannels, argAudioIn, 
	 		argNumInChannels;
	 	addAction = addActions[addAction];
	 	target = argTarget.asUGenInput ?? {1};
	 	server = server ?? {Server.default};
	 	version = 0;
	 	inputOptions = [];
	 	timer = CtkTimer.new(starttime);
		(condition.isKindOf(Env) and: {condition.releaseNode.isNil}).if({
			endtime = condition.times.sum + starttime;
			endtimeud = false
			}, {
			endtime = starttime;
			endtimeud = true;
			});
		routeOut = 0;
		routeAmp = 0;
		inc = 0;
		parameters = [];
		playinit = true;
		notes = [];
		watch = [];
		function = function ?? {noFunc = true; {}};
		numChannels = argNumChannels;
		argoutbus.isKindOf(CtkAudio).not.if({
			outbus = CtkAudio.new(numChannels, argoutbus.asUGenInput, server);
		}, {
			outbus = argoutbus;
			(outbus.numChans != numChannels).if({
				"The CtkAudio's numChans parameter that you are using for outbus doesn't match the numChannels argument to this instance of CtkPMod".warn;
			});
		});
		// routebus gets made when the CtkPMod is played
		argAudioIn.notNil.if({
			argAudioIn.isKindOf(CtkAudio).not.if({
				audioIn = CtkAudio.new(argNumInChannels, argAudioIn.asUGenInput, server);
			}, {
				audioIn = argAudioIn;
			})
		}, {
			audioIn = nil;
		});
		// when we 'play', if condition is an Env - send it in, otherwise make a dummy Env of 1
		envsd = CtkSynthDef(("ctkpmod_"++numChannels).asSymbol, {arg inbus, outbus, pgate = 1, amp = 1, 
				timeScale = 1, lag = 0.1, routeOut = 0, routeAmp = 0;
			var env, sig;
			env = EnvGen.kr(
				Control.names(\env).kr(Env.newClear(32)), 
				pgate, 
				1, 
				0, 
				timeScale, 
				doneAction: 13) * Lag2.kr(amp, lag);
			sig = In.ar(inbus, numChannels) * env;
			ReplaceOut.ar(inbus, sig);
			Out.ar(outbus, sig);
			Out.ar(routeOut, sig * routeAmp);
		});
 	}

 	// override addParameter
	addSetter {arg key, func, isGroup = false;
		this.addUniqueMethod((key.asString++"_").asSymbol, 
//			{arg object, newval; object.objargs[key] = newval.value; object;
			{arg object, newval; 
				object.objargs[key] = newval; func.value(object, newval); 
				isGroup.if({
					group.noteDict.keysValuesDo({arg node, thisNote;
						thisNote.args[(key).asSymbol].notNil.if({
							thisNote.perform((key ++ "_").asSymbol, newval);
						})
					})
				});
				object;
			});
		}
 	
 	addParameter {arg key, defaultVal, func;
	 	parameters = parameters.add([key, false]);
		this.addGetter(key, defaultVal);
		this.addSetter(key, func);
		^this;
		}
	
	addGroupParameter {arg key, defaultVal, func;
		parameters = parameters.add([key, true]);
		this.addGetter(key, defaultVal);
		this.addSetter(key, func, true);
		^this;
	}
		
 	/* NOTE TO SELF! WHEN A NOTE FINISHES PLAYING, REMOVE IT FROM NOTES! 
 	use CtkNote:releaseFunc_ to remove from 'children' array!
 	... will CtkBuffers work as well?
 	*/ 
 	audioIn_ {arg newIn, numInChannels, update = false;
	 	var tmp;
	 	tmp = audioIn;
		newIn.notNil.if({
			newIn.isKindOf(CtkAudio).not.if({
				audioIn = CtkAudio.new(numInChannels, newIn.asUGenInput, server);
			}, {
				audioIn = newIn;
			})
		}, {
			audioIn = nil;
		});
		(update and: {isPlaying}).if({
			audioIn.notNil.if({
				group.noteDict.keysValuesDo({arg id, thisNote;
					thisNote.isPlaying.if({
						thisNote.args.keysValuesDo({arg key, val;
							(val == tmp).if({
								thisNote.perform((key ++ "_").asSymbol, audioIn);
							})
						})
					})
				})
			})
		})
			
 	}

	amp_ {arg newAmp, runAmpFunc = true;
		amp = newAmp;
		runAmpFunc.if({ampFunc.value(this, amp)});
		isPlaying.if({
			envSynth.amp_(newAmp);
		});
		hasGUI.if({
			gui.updateAmp(newAmp.ampdb, false)
		});
	}
	
	addInputOptions {arg ... ctkAudios;
		inputOptions = inputOptions ++ ctkAudios.flat;	
	}
	
	value {arg recPath, timeStamp = true, hFormat, sFormat, updateGUI = true;
		this.play(recPath, timeStamp, hFormat, sFormat, updateGUI);
	}
	
	ctkPEvents_ {arg pevent, add = true;
		ctkPEvents = pevent;
		add.if({pevent.watchedMods = pevent.watchedMods.add(this)});
	}
	
	play {arg recPath, timeStamp = true, hFormat, sFormat, updateGUI = true;
		var initSched, tmp;
		Routine.run({
			server.serverRunning.if({
				isPlaying.not.if({
					isPlaying = true;
					hasGUI.if({
						updateGUI.if({										{gui.startButton.value_(1)}.defer;
						})
					});
					timer.play;
					this.setup(recPath, timeStamp = true, hFormat, sFormat);
					playinit.if({
						tmp = timer.next; // just in case it is accidentally overridden in initFunc...
						this.initPlay;
						timer.next_(tmp); 
					});
					0.01.wait;
					hdr.notNil.if({
						hdr.record;
					});
					clock.sched(starttime + CtkObj.latency, {
						timer.next_(nil);
						function.value(this, group, routebus, inc, audioIn, server);
						// ... set it back again
						this.run; // plays the notes array
						this.checkCond.if({
							timer.next;
						}, {
							initSched = (endtime > timer.now).if({endtime - timer.now}, {0.1});
							timer.clock.notNil.if({
								timer.clock.sched(initSched, {
									(group.children.size == 0).if({
										this.clear;
									}, {
										0.1;
									})
								})
							}, {
								this.clear;
							});
						});
					})
				})
			}, {
				"Please boot the Server before trying to play an instance of CtkPMod".warn;
			})
		})
	}
	
	initPlay {
		var theEnv;
		condition.isKindOf(Env).if({
			theEnv = condition;
		}, {
			theEnv = Env([1, 1, 1], [0.1, 0.1], \lin, 1);
		});
		initFunc.value(this, group, routebus, 0, audioIn, server);
		notes = notes.add(
			envSynth = envsd.new(0.0, addAction: \tail, target: wrapGroup, server: server)
				.inbus_(routebus).outbus_(outbus).env_(theEnv).amp_(amp).routeOut_(routeOut)
				.routeAmp_(routeAmp);
		);
//		hdr.notNil.if({
//			hdr.record;
//		});
		playinit = false;
	}
	
	setup {arg recPath, timeStamp = true, hFormat, sFormat;
		routebus = CtkAudio.play(outbus.numChans, server: server);
		wrapGroup = CtkGroup.play(0.0, addAction: addAction, target: target, server: server);
		group = CtkGroup.play(0.01, addAction: \head, target: wrapGroup, server: server);
		clock = timer.clock;
		responder.notNil.if({
			responder.add;
		});
		recPath = recPath ?? {recordPath};
		recPath.notNil.if({
			hdr = HDR.new(server, Array.fill(numChannels, {arg i; routebus.bus + i}),
				3, wrapGroup.node, "", recPath ++ id, hFormat ?? {headerFormat},
				sFormat ?? {sampleFormat}, 1, timeStamp);
		});
		cper = {this.free};
		CmdPeriod.add(cper);
	}
	
	run {
		isPlaying.if({
			notes.do({arg me;
				clock.sched(me.starttime, {me.setStarttime(0.0); me.play(group)});
			});
		});
		notes = [];
		inc = inc + 1;
	}
	
	routeOut_ {arg newOut;
		routeOut = newOut;
		isPlaying.if({
			envSynth.routeOut_(routeOut)
		});
	}
	
	routeAmp_ {arg newAmp;
		routeAmp = newAmp;
		isPlaying.if({
			envSynth.routeAmp_(routeAmp)
		});
	}
	
	// duration says total duration! Release will happen at starttime + duration - releaseTime
	score {arg duration;
		var relTime, envCopy, tmp, score;
		score = CtkScore.new;
		condition.isKindOf(Env).if({
			envCopy = condition.copy;
			tmp = condition;
			condition = envCopy;
			relTime = condition.releaseTime;
		}, {
			relTime = 0;
		});
		score.add(
			routebus = CtkAudio.new(outbus.numChans, server: server);
			wrapGroup = CtkGroup.new(addAction: addAction, target: target, server: server);
			group = CtkGroup.new(addAction: \head, target: wrapGroup, server: server);
			);
			
		condition = tmp;
		^score;
	}
	
	function_ {arg newFunction;
		noFunc = newFunction.notNil;
		function = newFunction;
		}

	responder_ {arg aResponder;
		aResponder.isKindOf(OSCresponder).if({
			responder = aResponder;
		}, {
			"Only OSCresponders and its subclasses can be set as a CtkPMod responder".warn;
		});
		isPlaying.if({
			responder.add
		});
	}
	
	next_ {arg inval;
		timer.next_(inval);
		}
	
	curtime {
		^timer.curtime;
		}

	now {
		^timer.now;
		}

	checkCond {
		case
			// prevent inf loops
			{
			(timer.next == nil)// and: {noFunc.not}
			} {
			^false; // for now - function.notNil;
			} {
			condition.isKindOf(Boolean) || condition.isKindOf(Function)
			} {
			^condition.value(timer, inc)
			} {
			condition.isKindOf(SimpleNumber)
			} {
			^inc < condition
			} {
			condition.isKindOf(Env)
			} {
			^condition.releaseNode.isNil.if({
				timer.now < (condition.times.sum + starttime);
				}, {
				(isReleasing || (releaseTime < condition.releaseTime))
				})
			} {
			true
			} {
			^false
			}
			
		}

	collect {arg ... ctkevents;
		var thisend;
		ctkevents = ctkevents.flat;
		endtimeud.if({
			ctkevents.do({arg ev;
				ev.endtime.notNil.if({
					thisend = ev.endtime + timer.now;
					(thisend > endtime).if({
						endtime = thisend
						})
					})
				})
			});
		notes = (notes ++ ctkevents).flat;
 		}
 		
	free {
		onReleaseFunc.value;
		this.clear;
		}
	
	release {
		isPlaying.if({
			hasGUI.if({
				{gui.startButton.value_(2)}.defer;
			});
			noFunc.if({noFunc = false});
			onReleaseFunc.value;
			condition.isKindOf(Env).if({
				condition.releaseNode.notNil.if({
					isReleasing = true;
					envSynth.release(key: \pgate);
					this.releaseSetup(condition.releaseTime);
					}, {
					this.free;
					})
				}, {
				this.free;
				})
			}, {
			"This CtkPMod is not playing".warn
			});
		}
		
	releaseSetup {arg reltime;
		clock.sched(reltime, {this.clear});
		}
		
	clear { 

		CmdPeriod.remove(cper);
		cper = nil;
		wrapGroup.free;
		group.free;
		routebus.free;
		responder.notNil.if({
			responder.remove;
		});
		hasGUI.if({
			{
				gui.startButton.value_(0)
			}.defer;
		});
		releaseFunc.value;
		internalReleaseFunc.value;
		isPlaying = false;
		isReleasing = false;
		playinit = true;
		inc = 0;
		hdr.notNil.if({hdr.stop; hdr = nil});
		clock.clear;
		clock.stop;
	 	timer = CtkTimer.new(starttime);
		}
	
	makeGUI {arg parent, bounds;
		hasGUI = true;
		^gui = CtkPModGUI(this, parent, bounds);
	}

 	*initClass {
 	}
 	

 	
}

// hold lots of CtkPModGUIs
CtkPModSheet {
	
}

CtkPModGUI {
	var <ctkPMod, parent, win, dec, width, <height, spec, relFunc, bounds;
	var <startButton, ampNum, ampSlide, paramMenu, valueBox, values, activeParam, curParam, paramField, isGroupParam;
	
	*new {arg ctkPMod, parent, bounds;
		^super.newCopyArgs(ctkPMod, parent).initCtkPModGUI(bounds);
	}
	
	initCtkPModGUI {arg argBounds;
		var id, ins, params;
		spec = [-120, 12, \db].asSpec;
		bounds = argBounds ?? {Rect(400, Window.screenBounds.height - 120, 1200, 60)};
		parent.notNil.if({
			win = parent;
			width = parent.bounds.width;
			dec = parent.view.decorator;
		}, {
			width = bounds.width;
			win = Window.new(id = ctkPMod.id, bounds);
			win.onClose_({
				ctkPMod.releaseFunc.removeFunc(relFunc); 
				ctkPMod.hasGUI = false;
				ctkPMod.isPlaying.if({ctkPMod.free})
			});
			dec = win.addFlowLayout(10@10, 5@5);
		});
		win.front;
		startButton = Button(win, (width * 0.1) @ 40)
			.states_([
				["Start\n" + id, Color.black, Color.green],
				["Release\n" + id, Color.black, Color.red],
				["Releasing\n" + id, Color.black, Color.yellow]
			])
			.action_({arg button;
				this.updatePlayState(button.value)
			});
		dec.shift(0, 5);
		ampNum = NumberBox(win, (width * 0.04) @ 30)
			.value_(ctkPMod.amp.ampdb.round(0.01))
			.action_({arg numBox;
				this.updateAmp(numBox.value);
			});
		ampSlide = Slider(win, (width * 0.28 - 10) @ 30)
			.value_( spec.unmap(ctkPMod.amp.ampdb) ) // map it!
			.action_({arg slide;
				this.updateAmp(spec.map(slide.value));
			});
		ins = ctkPMod.inputOptions.collect({arg me; me.label.asString});

		(ins.size > 0).if({
			StaticText(win, 50 @ 30)
				.string_("Input:");
			PopUpMenu(win, 80 @ 30)
				.items_(ins)
				.value_(
					ctkPMod.audioIn.notNil.if({
						ctkPMod.inputOptions.indexOf(ctkPMod.audioIn)
					})
				)		
				.action_({arg pm; this.updateInput(pm.value)});
		});
		StaticText(win, 80 @ 30)
			.string_("Parameters:");
		params = ctkPMod.parameters.collect({arg me; me[0].asString});
//		activeParam = params[0];
		PopUpMenu(win, 80 @ 30)
			.items_(params ?? {[""]})
			.value_(0)
			.action_({arg pm;
				this.setActiveParam(pm.value)
			});
		paramField = TextField(win, ((width * 0.55) - 440) @ 30)
			.string_(
//				curParam.postln;
				curParam.notNil.if({
//					ctkPMod.parameters[0].postln;
					curParam = ctkPMod.perform(ctkPMod.parameters[0]).asCompileString
				}, {
				})
			)
			.action_({arg field;
				field.value.interpret.postcs;
				ctkPMod.perform((activeParam ++ "_").asSymbol, field.value.interpret);
				isGroupParam.if({
					ctkPMod.group.noteDict.keysValuesDo({arg key, thisNote;
						thisNote.args[(activeParam).asSymbol].notNil.if({
							thisNote.perform((activeParam ++ "_").asSymbol,
								field.value.interpret);
						})
					})
				})
			});
		dec.shift(0, -5);
		Button(win, 50 @ 40)
			.states_([
				["Post", Color.black, Color.white]]
				)
			.action_({
				this.postParams;
			});
			
		(ctkPMod.parameters.size > 0).if({
			this.setActiveParam(0);		
		})
	}
	
	updatePlayState {arg stateIdx;
		case
			{stateIdx == 0}
			{				
				ctkPMod.isReleasing.if({
					startButton.value_(2)
				})
			}
			{stateIdx == 1}
			{
				relFunc = {{startButton.value_(0)}.defer; ctkPMod.releaseFunc.removeFunc(relFunc)};
				ctkPMod.releaseFunc_(ctkPMod.releaseFunc.addFunc(relFunc));
				ctkPMod.play(updateGUI: false);
			}
			{stateIdx == 2}
			{
				ctkPMod.release;
			}	
	}
	
	updateAmp {arg newAmp, updatePmod = true;
		{
			ampNum.value_(newAmp.round(0.01));
			ampSlide.value_(spec.unmap(newAmp));
		}.defer;
		updatePmod.if({ctkPMod.amp_(newAmp.dbamp)});
	}
	
	// is there a way to 
	updateInput {arg idx;
		ctkPMod.audioIn_(ctkPMod.inputOptions[idx], update: true);
	}
	
	setActiveParam {arg idx;
		activeParam = ctkPMod.parameters[idx][0];
		isGroupParam = ctkPMod.parameters[idx][1];
		curParam = ctkPMod.perform(ctkPMod.parameters[idx][0]).asCompileString;
		paramField.string_(curParam)
	}
	
	postParams {
		ctkPMod.parameters.do({arg thisData;
			var thisParam, global;
			#thisParam, global = thisData;
			(thisParam.asString + "current value: " + ctkPMod.perform(thisParam).asCompileString).postln;
		})
	}
	
	close {
		win.close;	
	}
		
	addTrigger {arg label, action;
		{
			Button(win, 50 @ 40)
				.states_([
					[label.asString, Color.black, Color.white]]
					)
				.action_({
					action.value(ctkPMod)
				});
			win.refresh;
		}.defer;
	}

}

CtkPEvents : CtkObj {
	var <id, <amp, lag, init, kill, <erlisting, <events, <releases, <eventDict, <index, 
		first, <gui, <out, numChannels, scaler, <scalerSynth, <server, cperFunc, <>ampSpec, 
		show, place, pmodWins, timer, clock, <>onEvent, startTimes, <>ampFunc, <>watchedMods;
//	var recPath, timeStamp = true, hFormat, sFormat, updateGUI = true;

	*new {arg erlisting, amp = 1, out, init, kill, id, lag = 0.1;
	 	^super.newCopyArgs(Dictionary.new, nil, id, amp, lag).initCtkPEvents(erlisting, out, init, kill);
	}
	
	initCtkPEvents {arg argErlisting, argOut, argInit, argKill;
		ampSpec = [-90, 12, \db].asSpec;
		eventDict = IdentityDictionary.new;
		index = 0;
		watchedMods = [];
		this.erlisting_(argErlisting);
		init = argInit; 
		init.isKindOf(CtkPMod).if({
			init.ctkPEvents_(this, false);
		});
		kill = argKill;
		kill.isKindOf(CtkPMod).if({
			kill.ctkPEvents_(this, false);
		});
		startTimes = Dictionary.new;
		timer = CtkTimer.new;
		clock = timer.clock;
		first = true;
		server = [];
		out = [];
		numChannels = [];
		argOut.notNil.if({
			argOut.asArray.do({arg thisOut, i;
				thisOut.isKindOf(CtkAudio).if({
					out = out.add(thisOut);
					numChannels = numChannels.add(thisOut.numChans);
				}, {
					(thisOut.isKindOf(SimpleNumber)).if({
						(thisOut == 0).if({
							server = server.add(Server.default);
							out = out.add(CtkAudio.play(server[i].numOutputBusChannels, 0, Server.default));
							numChannels = numChannels.add(Server.default.numOutputBusChannels);
						}, {
							out = out.add(CtkAudio.play(2, thisOut, Server.default));
							numChannels = numChannels.add(2);
						})
					}, {
						out = out.add(CtkAudio.play(2, 0, Server.default));
						numChannels = numChannels.add(2);
					})
				})
			});
		});
//		screenHeight = Window.screenBounds.height;
		pmodWins = [];
		scaler = [];
		out.do({arg thisOut;
			scaler = scaler.add(
				CtkSynthDef(("ctkpevents"++thisOut.numChans.asStringToBase(10, 3)).asSymbol, {arg inbus, amp, lag = 0.1;
				ReplaceOut.ar(inbus, In.ar(inbus, thisOut.numChans) * Lag2.kr(amp, lag));
			});
			)
		});
	}
	
	erlisting_ {arg anErlisting;
		#events, releases = anErlisting.flop;
		events.do({arg evs;
			evs = evs.asArray;
			evs.do({arg thisEv;
				thisEv.isKindOf(CtkPMod).if({
					thisEv.ctkPEvents_(this);
					eventDict.add(thisEv.id -> thisEv);
				})
			});
		});
	}
	
	index_ {arg anIndex;
		index = anIndex;
		// GUI update later?
		gui.notNil.if({
			gui.indexView.value_(index)
		});
	}
		
	play {arg anIndex, update = true;
		var tmp;
		update.if({
			this.index_(anIndex);
			this.next;
		}, {
			tmp = index;
			this.index_(anIndex);
			this.next;
			this.index_(tmp);
		});
	}
	
	next {
		first.if({
			first = false;
			cperFunc = {this.kill};
			CmdPeriod.add(cperFunc);
			scalerSynth = [];
			out.do({arg thisOut, i;				
				scalerSynth = scalerSynth.add(
					scaler[i].new(addAction: \after, target: 1, server: thisOut.server)
						.inbus_(thisOut).amp_(amp).play;
					)
			});
			init.value;
			timer.play;
			startTimes.add(0 -> this.now);
		});
		(index < events.size).if({
			startTimes.add(index -> this.now);
			events[index].asArray.do({arg thisEv;
				var evGui;
				thisEv.isKindOf(CtkPMod).if({
					thisEv.play;
					startTimes.add(thisEv.id -> this.now);
					show.if({
						{
							evGui = thisEv.makeGUI(bounds: Rect(400, Window.screenBounds.height - 120 - (80 * place), 1200, 60));
							place = (place + 1) % 10;
							thisEv.internalReleaseFunc_({{evGui.close}.defer(0.2)});
						}.defer;
					})
				}, {
					thisEv.value(this);
				})
			});
			releases[index].asArray.do({arg thisRel;
				var sym;
				thisRel.isKindOf(CtkPMod).if({
					sym = thisRel.id.asSymbol;
				}, {
					sym = thisRel.asSymbol;
				});
				eventDict[sym].notNil.if({
					eventDict[sym].release;
				}, {
					/// warn that a release was asked for that didn't exist!
					("Event with id of "++sym++" not found").warn;
				})
			});
			gui.notNil.if({
				gui.curEvString.string_("Current Event: "+index); 
			});
			this.index_(index + 1);
			onEvent.value(this);
			gui.window.front;
		}, {
			"No event at that index".warn;
		})
			
	}
	
	timeStamp {arg id = 0;
		^startTimes[id];
	}
	
	kill {
		kill.play;
		first = true;
		CmdPeriod.remove(cperFunc);
		scalerSynth.do({arg thisSynth;
			thisSynth.isPlaying.if({
				thisSynth.free
			})
		});
//			
//		(scalerSynth.notNil and: {scalerSynth.isPlaying}).if({
//			scalerSynth.free;
//		});
		(events.flat ++ init).do({arg thisEv;
			thisEv.isPlaying.if({
				thisEv.free;
			})
		});
		gui.notNil.if({
			gui.curEvString.string_("No events running");
		});
		kill.clear;
		timer.free;
		timer = CtkTimer.new;
		clock = timer.clock;
	}
	
	reset {
		(events.flat).do({arg thisEv;
			thisEv.isPlaying.if({
				thisEv.free;
			})
		});
		gui.notNil.if({
			gui.curEvString.string_("No events running");
		});
		this.index_(0);	
	}
	
	amp_ {arg newAmp, runAmpFunc = true;
		amp = newAmp;
		runAmpFunc.if({ampFunc.value(this, amp)});
		first.not.if({
			scalerSynth.do({arg thisSynth;
				thisSynth.amp_(amp);
			})
		});
		gui.notNil.if({
			gui.ampNum.value_(newAmp.ampdb.round(0.001));
			gui.ampSlide.value_(ampSpec.unmap(newAmp.ampdb))
		})
	}
	
	releaseAll {
//		first = true;
		events.flat.do({arg thisEv;
			thisEv.isPlaying.if({
				thisEv.release;
			})
		});
	}
	
	makeGUI {arg showProcs, parent, bounds;
		show = showProcs;
		place = 0;
		gui = CtkPEventsGUI(this, parent, bounds);
	}
	
	now {
		^timer.now;
	}
	
	playingEvents {
		^watchedMods.flat.select({arg thisEv; thisEv.isPlaying});
	}
	
	at {arg id;
		^eventDict[id]
	}
}

CtkPEventsGUI {
	var <ctkPEvent, parent, <window, <dec, width, height, spec, bounds;
	var <indexView, <ampNum, <ampSlide, <curEvString;
	
	*new {arg ctkPEvent, parent, bounds;
		^super.new.initCtkPEventsGUI(ctkPEvent, parent, bounds);
	}
	
	close {
		window.close;
	}
	
	initCtkPEventsGUI {arg argCtkPEvent, argParent, argBounds;
		var widgetHeight, widgetWidth, marginX, marginY, gapX, gapY, font;
		font = Font("Arial", 20);
		ctkPEvent = argCtkPEvent;
		parent = argParent;
		bounds = argBounds ?? {Rect(20, Window.screenBounds.height, width = 400, height = 255)};
		parent.isNil.if({
			window = Window.new(ctkPEvent.id, bounds);
			dec = window.addFlowLayout((marginX = 20) @ (marginY = 20), (gapX = 10) @ (gapY = 5));
			window.front;
		}, {
			// perhaps in this case we should make a view with bounds???
			window = parent;
			width = parent.view.bounds.width;
			height = parent.view.bounds.height;
			parent.view.decorator.nitNil.if({
				marginX = parent.view.decorator.margin.x;
				marginY = parent.view.decorator.margin.y;
				gapX = parent.view.decorator.gap.x;
				gapY = parent.view.decorator.gap.x;
			}, {
				dec = window.addFlowLayout((marginX = 20) @ (marginY = 20), (gapX = 10) @ (gapY = 5));
			});
		});
		widgetWidth = (width - (marginX * 2) - (gapX * 2) * 0.333).asInteger;
		widgetHeight = (height - (marginY * 2) - (gapY * 3) * 0.25).asInteger;
		
		Button(window, widgetWidth @ widgetHeight)
			.font_(font)
			.states_([
				["Next Event", Color.black, Color.rand]
			])
			.action_({arg button;
				ctkPEvent.next;
			});
		
		dec.shift(0, 10);
		
		indexView = NumberBox(window, (widgetWidth * 0.6) @ widgetHeight - 20)
			.font_(font)
			.value_(0)
			.action_({arg numBox;
				ctkPEvent.index_(numBox.value);
				window.view.children[0].focus(true);
			});
		
		dec.shift(0, -10);
		dec.nextLine;
		
		curEvString = StaticText(window, width @ widgetHeight)
			.font_(font)
			.string_("No events running");
			
		dec.shift(0, 10);
		
		ampSlide = Slider(window, (widgetWidth * 2 + gapX) @ (widgetHeight * 0.6))
			.value_(ctkPEvent.ampSpec.unmap(ctkPEvent.amp.ampdb)) // setup the ControlSpec!
			.action_({arg slider;
				ctkPEvent.amp_(ctkPEvent.ampSpec.map(slider.value).dbamp);
				window.view.children[0].focus(true);
			});
			
		ampNum = NumberBox(window, widgetWidth @ (widgetHeight * 0.6))
			.font_(font)
			.value_(0)
			.action_({arg numBox;
				ctkPEvent.amp_(numBox.value.dbamp);
				window.view.children[0].focus(true);
			});
			
		dec.shift(0, 10);
		dec.nextLine;
		
		Button(window, widgetWidth @ widgetHeight)
			.font_(font)
			.states_([
				["Release All", Color.black, Color.white]
			])
			.action_({arg button;
				ctkPEvent.releaseAll;
				window.view.children[0].focus(true);
			});
		
		Button(window, widgetWidth @ widgetHeight)
			.font_(font)
			.states_([
				["Reset", Color.black, Color.white]
			])
			.action_({arg button;
				ctkPEvent.reset;
				window.view.children[0].focus(true);
			});
		
		Button(window, widgetWidth @ widgetHeight)
			.font_(font)
			.states_([
				["Kill all", Color.black, Color.white]
			])
			.action_({arg button;
				ctkPEvent.kill;
			});
			
		window.view.children[0].focus(true);
	}
	
}

